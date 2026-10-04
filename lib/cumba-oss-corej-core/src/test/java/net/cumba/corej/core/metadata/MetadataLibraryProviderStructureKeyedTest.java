package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.metadata.MetadataLibraryProvider.DeclaredAdamProduct;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} §4.1 / D9 / D2 on a synthetic two-product
 * declaration, read end to end through {@code ExprCompiler.libraryVariableMetadata} (tier 0) with
 * the structures detected as the scope gate detects them. The first product (ADSL + BDS) is also
 * the library view, so its answers carry the column shape; the second (OCCDS) is served from its
 * stored variables.
 */
class MetadataLibraryProviderStructureKeyedTest
{

    private static final String LT = LibraryTemplates.LABEL_TEMPLATE_KEY;

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static final String IG = "standards/adam/adamig-9-9";

    private static final String OCCDS = "standards/adam/adam-occds-9-9";

    private static StoredVariable v(String name, String label, String type, String ordinal)
    {
        return StoredVariable.builder().name(name).label(label).ordinal(ordinal).core("Perm")
                .simpleDatatype(type).build();
    }


    private static StoredDataStructure ds(String name, String className, StoredVariable... vars)
    {
        return new StoredDataStructure(name, name, "1", className, null,
                List.of(new StoredVariableSet("Variables", "Variables", "1", List.of(vars), null)),
                null);
    }

    private static final StoredProduct ADAMIG = StoredProduct.builder().key(IG).name("ADaMIG")
            .version("9-9")
            .dataStructures(List.of(
                    ds("ADSL", "SUBJECT LEVEL ANALYSIS DATASET",
                            v("STUDYID", "Study Identifier", "Char", "1"),
                            v("ITTFL", "Intent-To-Treat Population Flag", "Char", "2"),
                            v("TRTSDT", "Date of First Exposure to Treatment", "Num", "3"),
                            v("TRTxxP", "Planned Treatment for Period xx", "Char", "4"),
                            v("TRTSDTF", "Date of First Exposure Imput. Flag", "Char", "5")),
                    ds("BDS", "BASIC DATA STRUCTURE", v("PARAMCD", "Parameter Code", "Char", "1"),
                            v("AVAL", "Analysis Value", "Num", "2"),
                            v("ASTDT", "Analysis Start Date", "Num", "3"),
                            v("ANLzzFL", "Analysis Flag zz", "Char", "4"),
                            v("AVALCATy", "Analysis Category y", "Char", "5"),
                            v("*DT", "{Date}", "Num", "6"), v("*SDT", "{Start Date}", "Num", "7"),
                            v("*DTF", "{Date Imputation Flag}", "Char", "8"),
                            v("*SDTF", "{Start Date Imputation Flag}", "Char", "9"))))
            .build();

    private static final StoredProduct OCCDS_PRODUCT = StoredProduct.builder().key(OCCDS)
            .name("OCCDS").version("9-9")
            .dataStructures(List.of(ds("OCCDS", "OCCURRENCE DATA STRUCTURE",
                    v("AETERM", "Reported Term for the Adverse Event", "Char", "1"),
                    v("ADURN", "Analysis Duration (N)", "Num", "2"),
                    v("AOCCzzFL", "1st Occurrence of ...", "Char", "3"))))
            .build();

    private static MetadataLibraryProvider provider()
    {
        CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary.fromStoredAdam("adamig",
                "9-9", ADAMIG, List.of(new StoredCtPackage(null, List.of())), List.of(), List.of());
        return new MetadataLibraryProvider(library, List.of(new DeclaredAdamProduct(IG, ADAMIG),
                new DeclaredAdamProduct(OCCDS, OCCDS_PRODUCT)), "adamig", "9-9");
    }


    private static Map<String, String> read(MetadataProvider p, IDataTable table, String column)
    {
        return ExprCompiler.libraryVariableMetadata(p, null, table, NO_RESOLVER,
                table.getMetaData().getName(), column);
    }


    private static IDataTable adlb()
    {
        return MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1").col("PARAMCD", "ALB")
                .col("AVAL", "1").col("ASTDT", "1").col("ANL01FL", "Y").col("AVALCAT1", "LOW")
                .col("ITTFL", "Y").col("TRTSDT", "1").col("TRT01P", "A").col("RANDSDT", "1")
                .col("XXDT", "1").col("XXSDTF", "D").col("SPONFL", "Y").name("ADLB").build();
    }


    @Test
    void aBdsDatasetResolvesThroughItsStructureNotItsName()
    {
        MetadataProvider p = provider();
        IDataTable adlb = adlb();
        assertEquals(Map.of(), p.getVariableMetadata("ADLB", "ASTDT"),
                "the domain-keyed read never reached BDS — the defect this plan closes");
        Map<String, String> astdt = read(p, adlb, "ASTDT");
        assertEquals("ASTDT", astdt.get("name"));
        assertEquals("Analysis Start Date", astdt.get("label"));
        assertEquals("Analysis Start Date", astdt.get(LT));
        assertEquals("Num", astdt.get("simpleDatatype"), "the library view's column shape");
        assertEquals(Map.of(), read(p, adlb, "SPONFL"), "a sponsor variable resolves nowhere");
    }


    @Test
    void indexTemplatesAdslCopiesAndStemsAnswerInTheirTiers()
    {
        MetadataProvider p = provider();
        IDataTable adlb = adlb();
        Map<String, String> anl = read(p, adlb, "ANL01FL");
        assertEquals("ANLzzFL", anl.get("name"));
        assertEquals("Analysis Flag 01", anl.get("label"));
        assertEquals("Analysis Flag 01 ...", anl.get(LT));
        assertEquals("Analysis Category 1 ...", read(p, adlb, "AVALCAT1").get(LT));
        // D9: ADSL variables copied into BDS
        assertEquals("Intent-To-Treat Population Flag", read(p, adlb, "ITTFL").get("label"));
        assertEquals("TRTSDT", read(p, adlb, "TRTSDT").get("name"), "ADSL literal beats *SDT");
        assertEquals("Planned Treatment for Period 01", read(p, adlb, "TRT01P").get("label"));
        // the stems, longest suffix
        assertEquals(Map.of("name", "*SDT", LT, "{Start Date}"), read(p, adlb, "RANDSDT"));
        assertEquals("*DT", read(p, adlb, "XXDT").get("name"));
        assertEquals("*SDTF", read(p, adlb, "XXSDTF").get("name"));
    }


    @Test
    void anOccurrenceDatasetIsServedFromTheSecondProductsStoredVariables()
    {
        MetadataProvider p = provider();
        IDataTable adae = MockTable.of().col("STUDYID", "P1").col("AETERM", "HEADACHE")
                .col("ADURN", "1").col("AOCC01FL", "Y").col("ITTFL", "Y").col("RANDSDT", "1")
                .name("ADAE").build();
        Map<String, String> adurn = read(p, adae, "ADURN");
        assertEquals("Analysis Duration (N)", adurn.get("label"));
        assertEquals("Num", adurn.get("simpleDatatype"));
        assertNull(adurn.get("role"), "ADaM publishes no role");
        Map<String, String> aocc = read(p, adae, "AOCC01FL");
        assertEquals("AOCCzzFL", aocc.get("name"));
        assertEquals("1st Occurrence of ...", aocc.get(LT), "an IG ellipsis takes no second tail");
        assertEquals("Intent-To-Treat Population Flag", read(p, adae, "ITTFL").get("label"),
                "D9 reaches OCCDS datasets too");
        assertEquals("*SDT", read(p, adae, "RANDSDT").get("name"),
                "the stems are pooled across every structure, not BDS's own datasets only");
    }


    @Test
    void theAdslDatasetKeepsItsDomainKeyedAnswersAndATemplateSpelledColumnHoldsNoTemplate()
    {
        MetadataProvider p = provider();
        IDataTable adsl = MockTable.of().col("STUDYID", "P1").col("TRT01P", "A").col("TRTXXP", "B")
                .name("ADSL").build();
        Map<String, String> studyid = new java.util.LinkedHashMap<>(read(p, adsl, "STUDYID"));
        assertEquals("Study Identifier", studyid.remove(LT),
                "plus the template, which is the label");
        assertEquals(p.getVariableMetadata("ADSL", "STUDYID"), studyid,
                "otherwise byte-for-byte the domain-keyed answer");
        assertEquals("TRTxxP", read(p, adsl, "TRT01P").get("name"));
        Map<String, String> spelled = read(p, adsl, "TRTXXP");
        assertEquals("TRTxxP", spelled.get("name"));
        assertEquals("Planned Treatment for Period xx", spelled.get("label"));
        assertFalse(spelled.containsKey(LT));
    }


    @Test
    void theColumnOrderOfAStructureIsPublishedAsIsAndFallsBackToTheDomain()
    {
        MetadataLibraryProvider p = provider();
        assertEquals(
                List.of("PARAMCD", "AVAL", "ASTDT", "ANLzzFL", "AVALCATy", "*DT", "*SDT", "*DTF",
                        "*SDTF"),
                p.getColumnOrderForStructures(List.of(AdamDataStructureDetector.BDS), List.of(),
                        "ADLB"));
        assertEquals(List.of("AETERM", "ADURN", "AOCCzzFL"), p.getColumnOrderForStructures(
                List.of(AdamDataStructureDetector.OCCDS), List.of(), "ADAE"));
        assertEquals(p.getColumnOrder("ADSL"),
                p.getColumnOrderForStructures(List.of("NO SUCH STRUCTURE"), List.of(), "ADSL"),
                "no chain: the domain's order");
        assertTrue(p.getColumnOrderForStructures(List.of("NO SUCH STRUCTURE"), List.of(), "ADLB")
                .isEmpty());
    }


    @Test
    void aReadNothingResolvesFallsBackToTheDomainKeyedAnswer()
    {
        MetadataLibraryProvider p = provider();
        // A dataset literally named after a structure, read under a token no product publishes:
        // no own structure, PARAMCD is no ADSL variable and no stem — the name-keyed read answers.
        IDataTable bds = MockTable.of().col("PARAMCD", "ALB").name("BDS").build();
        assertFalse(p.getVariableMetadata("BDS", "PARAMCD").isEmpty());
        assertEquals(p.getVariableMetadata("BDS", "PARAMCD"), p.getVariableMetadata(bds,
                List.of("NO SUCH STRUCTURE"), List.of(), "BDS", "PARAMCD"));
    }
}
