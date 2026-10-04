package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.metadata.MetadataLibraryProvider.DeclaredAdamProduct;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} D10 — the OCCDS product inferred from the ADaMIG's
 * generation inside {@link DeclaredAdamProducts#assemble}, the single construction site.
 */
class DeclaredAdamProductsOccdsInferenceTest
{

    private static final String IG10 = "standards/adam/adamig-1-0";

    private static final String IG11 = "standards/adam/adamig-1-1";

    private static final String IG12 = "standards/adam/adamig-1-2";

    private static final String IG13 = "standards/adam/adamig-1-3";

    private static final String OCCDS10 = "standards/adam/adam-occds-1-0";

    private static final String OCCDS11 = "standards/adam/adam-occds-1-1";

    private static final String ADAE = "standards/adam/adam-adae-1-0";

    private static final String TIG = "standards/tig/1-0/adam";

    private static StoredProduct product(String key, String structureName, String className)
    {
        StoredVariable v = StoredVariable.builder().name("USUBJID")
                .label("Unique Subject Identifier").ordinal("1").core("Req").simpleDatatype("Char")
                .build();
        return StoredProduct.builder().key(key).name(key).version("x")
                .dataStructures(
                        List.of(new StoredDataStructure(structureName, structureName, "1",
                                className, null, List.of(new StoredVariableSet("Identifier",
                                        "Identifier Variables", "1", List.of(v), null)),
                                null)))
                .build();
    }

    private static final Map<String, StoredProduct> STORE = Map.of(//
            IG10, product(IG10, "ADSL", "ADSL"), //
            IG11, product(IG11, "ADSL", "ADSL"), //
            IG12, product(IG12, "ADSL", "ADSL"), //
            IG13, product(IG13, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET"), //
            OCCDS10, product(OCCDS10, "OCCDS", "OCCDS"), //
            OCCDS11, product(OCCDS11, "OCCDS", "OCCURRENCE DATA STRUCTURE"), //
            ADAE, product(ADAE, "ADAE", "ADAE"), //
            TIG, product(TIG, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET"));

    private static StoredProduct fetch(String key) throws IOException
    {
        StoredProduct p = STORE.get(key);
        if (p == null)
        {
            throw new IOException("no product " + key);
        }
        return p;
    }


    private static List<String> keys(List<DeclaredAdamProduct> declared)
    {
        return declared.stream().map(DeclaredAdamProduct::cacheKey).toList();
    }


    @Test
    void theOccdsOfTheAdamigsGenerationIsAppendedLast() throws IOException
    {
        assertEquals(List.of(IG11, OCCDS10), keys(DeclaredAdamProducts.assemble("adamig", "1-1",
                List.of(IG11), DeclaredAdamProductsOccdsInferenceTest::fetch)));
        assertEquals(List.of(IG12, OCCDS11), keys(DeclaredAdamProducts.assemble("adamig", "1-2",
                List.of(IG12), DeclaredAdamProductsOccdsInferenceTest::fetch)));
        assertEquals(List.of(IG13, OCCDS11), keys(DeclaredAdamProducts.assemble("adamig", "1-3",
                List.of(IG13), DeclaredAdamProductsOccdsInferenceTest::fetch)));
        // the -s/-v fallback (nothing ADaM-shaped declared) infers too
        assertEquals(List.of(IG11, OCCDS10), keys(DeclaredAdamProducts.assemble("adamig", "1-1",
                List.of(), DeclaredAdamProductsOccdsInferenceTest::fetch)));
    }


    @Test
    void anExplicitOccurrenceProductWinsWhateverItsVersion() throws IOException
    {
        assertEquals(List.of(IG13, OCCDS10),
                keys(DeclaredAdamProducts.assemble("adamig", "1-3", List.of(IG13, OCCDS10),
                        DeclaredAdamProductsOccdsInferenceTest::fetch)),
                "a declared OCCDS 1.0 under ADaMIG 1.3 is the user's choice");
        assertEquals(List.of(OCCDS11, IG11), keys(DeclaredAdamProducts.assemble("adamig", "1-1",
                List.of(OCCDS11, IG11), DeclaredAdamProductsOccdsInferenceTest::fetch)));
        assertEquals(List.of(IG11, ADAE),
                keys(DeclaredAdamProducts.assemble("adamig", "1-1", List.of(IG11, ADAE),
                        DeclaredAdamProductsOccdsInferenceTest::fetch)),
                "adam-adae-1-0 publishes an occurrence structure: explicit, nothing inferred");
    }


    @Test
    void nothingIsInferredForAdamig10OrATigLeg() throws IOException
    {
        assertEquals(List.of(IG10), keys(DeclaredAdamProducts.assemble("adamig", "1-0",
                List.of(IG10), DeclaredAdamProductsOccdsInferenceTest::fetch)));
        assertEquals(List.of(TIG), keys(DeclaredAdamProducts.assemble("tig", "1-0", List.of(TIG),
                DeclaredAdamProductsOccdsInferenceTest::fetch)));
    }


    @Test
    void anInferredProductTheSourceLacksIsSkippedNotADegradedRun() throws IOException
    {
        assertEquals(List.of(IG11),
                keys(DeclaredAdamProducts.assemble("adamig", "1-1", List.of(IG11), key ->
                {
                    if (OCCDS10.equals(key))
                    {
                        throw new IOException("store has no " + key);
                    }
                    return fetch(key);
                })));
        // the declared product itself still throws, as before
        assertThrows(IOException.class,
                () -> DeclaredAdamProducts.assemble("adamig", "1-1",
                        List.of("standards/adam/adamig-7-7"),
                        DeclaredAdamProductsOccdsInferenceTest::fetch));
    }
}
