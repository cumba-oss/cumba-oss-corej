package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.store.RealCorpusLocator;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.seed.PickleStoreSeeder;
import net.cumba.corej.core.metadata.store.seed.StoreSeedOptions;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} on OUR metadata store seeded from the real corpus
 * (the seeding is the one sanctioned use of the pickle files; every read below is a store read):
 * the four mechanisms behind the nine AD0018 label findings on the PhUSE pilot (plan §8) — the
 * structure-keyed BDS read, the D10-inferred OCCDS 1.0 for an ADaMIG 1.1 run, the D9 ADSL copy, and
 * the index template — plus the 1.0 case where nothing is inferred. Skipped, with the named reason,
 * only where the corpus is absent everywhere.
 */
class AdamTemplateRealStoreTest
{

    private static final String LT = LibraryTemplates.LABEL_TEMPLATE_KEY;

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static @Nullable StoreMetadataProviderFactory realStore;

    private static synchronized @Nullable StoreMetadataProviderFactory realStoreFactory()
        throws IOException
    {
        Optional<Path> dir = RealCorpusLocator.locate();
        if (dir.isEmpty())
        {
            return null;
        }
        if (realStore == null)
        {
            Path store = Files.createTempDirectory("corej-template-store").resolve("store.zip");
            new PickleStoreSeeder(new LocalPickleSource(dir.get()))
                    .seed(StoreSeedOptions.of(store));
            realStore = StoreMetadataProviderFactory.open(store);
        }
        return realStore;
    }


    private static MetadataProvider adamig(String version) throws IOException
    {
        StoreMetadataProviderFactory factory = realStoreFactory();
        assumeTrue(factory != null, RealCorpusLocator.ABSENT_MESSAGE);
        return factory.forAdam("adamig", version, List.of("standards/adam/adamig-" + version),
                List.of(), List.of()).orElseThrow();
    }


    private static Map<String, String> read(MetadataProvider p, IDataTable table, String column)
    {
        return ExprCompiler.libraryVariableMetadata(p, null, table, NO_RESOLVER,
                table.getMetaData().getName(), column);
    }


    @Test
    void adamig11InfersOccds10AndAdaeResolvesAgainstIt() throws IOException
    {
        MetadataProvider p = adamig("1-1");
        assertEquals(List.of("standards/adam/adamig-1-1", "standards/adam/adam-occds-1-0"),
                p.declaredStructureKeyedProducts(), "D10: report provenance names the inference");
        IDataTable adae = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AETERM", "HEADACHE").col("AEDECOD", "HEADACHE").col("ADURN", "1")
                .col("ADURU", "DAYS").col("AOCCFL", "Y").col("ITTFL", "Y").name("ADAE").build();
        assertEquals("Analysis Duration (N)", read(p, adae, "ADURN").get("label"));
        assertEquals("Analysis Duration Units", read(p, adae, "ADURU").get("label"));
        assertEquals("1st Occurrence within Subject Flag", read(p, adae, "AOCCFL").get("label"));
        assertEquals("Intent-To-Treat Population Flag", read(p, adae, "ITTFL").get("label"),
                "D9 in an OCCDS dataset");
    }


    @Test
    void aBdsDatasetResolvesItsOwnAndItsAdslVariablesAndTheIndexTemplates() throws IOException
    {
        MetadataProvider p = adamig("1-1");
        IDataTable adadas = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("PARAMCD", "ACTOT").col("AVAL", "1").col("ANL01FL", "Y").col("ITTFL", "Y")
                .col("TRTSDT", "1").col("STARTDT", "1").col("SRCDOM", "QS").col("TRT01P", "A")
                .name("ADADAS").build();
        Map<String, String> anl = read(p, adadas, "ANL01FL");
        assertEquals("ANLzzFL", anl.get("name"));
        assertEquals("Analysis Flag 01", anl.get("label"), "PhUSE ADADAS ANL01FL under 1.1");
        assertEquals("Analysis Flag 01 ...", anl.get(LT));
        assertEquals("Intent-To-Treat Population Flag", read(p, adadas, "ITTFL").get("label"),
                "PhUSE ITTFL in ADADAS — the ADSL copy");
        assertEquals("TRTSDT", read(p, adadas, "TRTSDT").get("name"),
                "the ADSL literal, not the *SDT stem: no AD0509 false positive");
        assertEquals("Time-to-Event Origin Date for Subject",
                read(p, adadas, "STARTDT").get("label"), "PhUSE ADTTE STARTDT, a BDS literal");
        assertEquals("Source Data", read(p, adadas, "SRCDOM").get("label"), "PhUSE ADTTE SRCDOM");
        assertEquals("Planned Treatment for Period 01", read(p, adadas, "TRT01P").get("label"));
    }


    @Test
    void adamig10InfersNothingAndTypesItsIndexTemplatesAsTheErrataSay() throws IOException
    {
        MetadataProvider p = adamig("1-0");
        assertEquals(List.of("standards/adam/adamig-1-0"), p.declaredStructureKeyedProducts());
        IDataTable adlb = MockTable.of().col("PARAMCD", "ALB").col("AVAL", "1").col("A1LO", "1")
                .col("RANDSDT", "1").name("ADLB").build();
        Map<String, String> a1lo = read(p, adlb, "A1LO");
        assertEquals("AyLO", a1lo.get("name"));
        assertEquals("Num", a1lo.get("simpleDatatype"),
                "§9.4 (iii) precondition: errata #282.33 corrects the 1.0 print's Char");
        assertEquals(Map.of("name", "*SDT", LT, "Start Date of ..."), read(p, adlb, "RANDSDT"),
                "ADaMIG 1.0 spells its stems with an ellipsis (D11)");
    }


    /**
     * T2 review H1, on our store: the inferred OCCDS product publishes {@code DECDORGw} twice in
     * one structure, as the MedDRA {@code PT in Original Dictionary w} and the WHO Drug
     * {@code Standardized Med Name in Orig Dict w}. A {@code DECDORG1} column therefore gets no
     * label and no template (it got the MedDRA one before, and a WHO Drug dataset a false AD0018);
     * the structure's other templates still answer.
     */
    @Test
    void decdorgwIsPublishedWithTwoLabelsAndSoResolvesToNothing() throws IOException
    {
        for (String version : List.of("1-1", "1-2", "1-3"))
        {
            MetadataProvider p = adamig(version);
            IDataTable adcm = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                    .col("CMTRT", "ASPIRIN").col("CMDECOD", "ASPIRIN").col("DECDORG1", "X")
                    .col("AOCC01FL", "Y").name("ADCM").build();
            Map<String, String> decdorg = read(p, adcm, "DECDORG1");
            assertNull(decdorg.get("label"), version + ": " + decdorg);
            assertNull(decdorg.get(LT), version + ": " + decdorg);
            assertEquals("AOCCzzFL", read(p, adcm, "AOCC01FL").get("name"),
                    version + ": control, the OCCDS template leg is reached");
        }
    }


    @Test
    void everyIgVersionPublishesTheEighteenStems() throws IOException
    {
        for (String version : List.of("1-1", "1-2", "1-3"))
        {
            MetadataProvider p = adamig(version);
            IDataTable adlb = MockTable.of().col("PARAMCD", "ALB").col("AVAL", "1")
                    .col("XXSDTF", "D").name("ADLB").build();
            Map<String, String> r = read(p, adlb, "XXSDTF");
            assertEquals("*SDTF", r.get("name"), version);
            assertEquals("{Start Date Imputation Flag}", r.get(LT), version);
            assertTrue(!r.containsKey("label"), "a stem phrase is never a label: " + version);
        }
    }
}
