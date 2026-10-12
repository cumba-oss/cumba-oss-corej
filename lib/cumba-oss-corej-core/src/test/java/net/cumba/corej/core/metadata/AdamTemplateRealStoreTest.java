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
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.LibraryLists;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.StubMetadataProvider;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Vector;
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
 * {@code PLAN-library-template-label-resolution} on OUR metadata store seeded from the real
 * corpus's two standards pickles, the products only (the seeding is the one sanctioned use of the
 * pickle files; every read below is a store read): the four mechanisms behind the nine AD0018 label
 * findings on the PhUSE pilot (plan §8) — the structure-keyed BDS read, the D10-inferred OCCDS 1.0
 * for an ADaMIG 1.1 run, the D9 ADSL copy, and the index template — plus the 1.0 case where nothing
 * is inferred. Skipped, with the named reason, only where the corpus is absent everywhere.
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
            // The products only (the StoreErrataRealCorpusTest pattern): every read here is an
            // ADaM product read with no CT package selected, and a directory holding just the two
            // standards pickles enumerates no CT package — seconds instead of the whole corpus.
            Path temp = Files.createTempDirectory("corej-template-store");
            Path source = Files.createDirectories(temp.resolve("products-only"));
            List<String> products = List.of("standards_details.pkl", "standards_models.pkl");
            for (String file : products)
            {
                Files.copy(dir.get().resolve(file), source.resolve(file));
            }
            Path store = temp.resolve("store.zip");
            new PickleStoreSeeder(new LocalPickleSource(source)).seed(StoreSeedOptions.of(store));
            // the seed input is spent; only the store stays behind, as before
            for (String file : products)
            {
                Files.delete(source.resolve(file));
            }
            Files.delete(source);
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
        assertEquals(
                Map.of("name", "*SDT", LT, "Start Date of ...", LibraryTemplates.stemKey("*DT"),
                        "Date of ...", LibraryTemplates.stemKey("*SDT"), "Start Date of ..."),
                read(p, adlb, "RANDSDT"),
                "ADaMIG 1.0 spells its stems with an ellipsis (D11); R2-3: each stem RANDSDT ends in"
                        + " serves its own phrase under its own key");
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


    /**
     * Round-2 ruling R2-1, on our store: {@code required_variables()} of an {@code ADAE} under the
     * D10-inferred OCCDS 1.0 names the AE variables ({@code --SEQ} → {@code AESEQ}, {@code --TERM}
     * → {@code AETERM}, {@code AESER} carrying {@code AE.AESER}) and not {@code CMTRT} (the store's
     * {@code CM.CMTRT}); the P5 stop reported {@code [--SEQ, --TERM, CMTRT]} missing here. The same
     * walk on an {@code ADCM} demands {@code CMTRT} and not {@code AESER}.
     */
    @Test
    void requiredVariablesOfAdaeUnderTheInferredOccdsAreTheAeVariablesAndNeverCmtrt()
        throws IOException
    {
        MetadataProvider p = adamig("1-1");
        IDataTable adae = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1").col("AESEQ", "1")
                .col("AETERM", "HEADACHE").col("AEDECOD", "HEADACHE").col("AESER", "N").name("ADAE")
                .build();
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AESER"), required(p, adae),
                "AESEQ / AETERM name AE");
        IDataTable adcm = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1").col("CMSEQ", "1")
                .col("CMTRT", "ASPIRIN").col("CMDECOD", "ASPIRIN").name("ADCM").build();
        assertEquals(List.of("STUDYID", "USUBJID", "CMSEQ", "CMTERM", "CMTRT"), required(p, adcm),
                "CMSEQ / CMTRT name CM: CMTRT demanded, AESER not");
        IDataTable unconfirmed = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AETERM", "HEADACHE").col("AEDECOD", "HEADACHE").name("ADAE").build();
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AESER"),
                required(p, unconfirmed),
                "no AESEQ: AETERM names AE (PLAN-occds-source-domain-by-ig D1/D2), so --SEQ is"
                        + " demanded as AESEQ and reported missing; CMTRT is not");
        IDataTable copiedAdslDecod = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AETERM", "HEADACHE").col("AEDECOD", "HEADACHE").col("AESER", "N")
                .col("DCDECOD", "COMPLETED").name("ADAE").build();
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AESER"),
                required(p, copiedAdslDecod),
                "owner ruling MED-1 (a): ADSL's copied DCDECOD is no evidence of a DC domain, so"
                        + " the dataset stays AE alone and the missing AESEQ is demanded (read as"
                        + " {AE, DC} before: no -- name demanded, AESEQ silent)");
    }


    /**
     * Round-2 T2 review H1, on our store: under a declared {@code adam-adae-1-0} the {@code ADAE}
     * structure publishes its AE variables as concrete names ({@code AESEQ}, {@code AETERM},
     * {@code AEDECOD}, {@code AEBODSYS}, {@code AESER} Req, each opening with {@code AE.<name>}).
     * An {@code ADAE} lacking {@code AESEQ} must still be asked for the AE names — its
     * {@code AETERM} names the domain {@code AE} ({@code PLAN-occds-source-domain-by-ig} D1) — so
     * the missing {@code AESEQ} is reported rather than disarming its own requirement (before
     * round-2's fix: only {@code STUDYID}, {@code USUBJID}).
     */
    @Test
    void requiredVariablesOfAdaeUnderAdamAdaeKeepTheAeNamesWithoutAeseq() throws IOException
    {
        StoreMetadataProviderFactory factory = realStoreFactory();
        assumeTrue(factory != null, RealCorpusLocator.ABSENT_MESSAGE);
        MetadataProvider p = factory.forAdam("adamig", "1-1",
                List.of("standards/adam/adamig-1-1", "standards/adam/adam-adae-1-0"), List.of(),
                List.of()).orElseThrow();
        List<Object> aeReq = List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AEDECOD", "AEBODSYS",
                "AESER");
        IDataTable noSeq = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AETERM", "HEADACHE").col("AEDECOD", "HEADACHE")
                .col("AEBODSYS", "NERVOUS SYSTEM DISORDERS").col("AESER", "N").name("ADAE").build();
        assertEquals(aeReq, required(p, noSeq),
                "AESEQ absent: still demanded (and so reported missing), with its AE siblings");
        IDataTable withSeq = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AESEQ", "1").col("AETERM", "HEADACHE").col("AEDECOD", "HEADACHE")
                .col("AEBODSYS", "NERVOUS SYSTEM DISORDERS").col("AESER", "N").name("ADAE").build();
        assertEquals(aeReq, required(p, withSeq), "control: AESEQ present, the same demand");
    }


    /**
     * {@code PLAN-occds-source-domain-by-ig} review H1, on our store: the source domain now comes
     * from the occurrence variables, never the dataset name, so an {@code ADAE} declared under
     * {@code adam-adae-1-0} that carries <b>no</b> occurrence variable ({@code --SEQ},
     * {@code --TERM}, {@code --TRT}) gives evidence of no domain at all. Its concrete AE names must
     * stay demanded — an empty evidence set never drops a carried name — or the missing
     * {@code AESEQ} / {@code AETERM} / {@code AEDECOD} would disarm their own requirement and
     * CDISC-AD9704 would go silent on exactly the dataset it should report.
     */
    @Test
    void requiredVariablesOfAdaeUnderAdamAdaeWithNoOccurrenceVariableKeepTheAeNames()
        throws IOException
    {
        StoreMetadataProviderFactory factory = realStoreFactory();
        assumeTrue(factory != null, RealCorpusLocator.ABSENT_MESSAGE);
        MetadataProvider p = factory.forAdam("adamig", "1-1",
                List.of("standards/adam/adamig-1-1", "standards/adam/adam-adae-1-0"), List.of(),
                List.of()).orElseThrow();
        IDataTable bare = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AEBODSYS", "NERVOUS SYSTEM DISORDERS").col("AESER", "N").name("ADAE").build();
        // No occurrence column, so no column heuristic: the define declares the structure.
        StubMetadataProvider define = new StubMetadataProvider().declares("ADAE",
                AdamDataStructureDetector.OCCDS, "ADVERSE EVENT");
        assertEquals(
                List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AEDECOD", "AEBODSYS", "AESER"),
                required(p, define, bare),
                "no occurrence variable: no domain evidence, so every carried AE name stays"
                        + " demanded (AESEQ, AETERM, AEDECOD reported missing)");
    }


    /**
     * Owner ruling MED-1 (a) of {@code PLAN-occds-source-domain-by-ig}, on our store: a
     * {@code --DECOD} column alone is no evidence of a domain. An {@code adam-adae-1-0}
     * {@code ADAE} carrying no AE occurrence variable but ADSL's copied {@code DCDECOD} therefore
     * has an empty evidence set, and every carried AE name stays demanded (review H1). Read as
     * {@code {DC}} before, the evidence named a different domain and all the AE names were dropped,
     * leaving only {@code STUDYID} and {@code USUBJID} — CDISC-AD9704 silent on an ADAE missing
     * AESEQ, AETERM, AEDECOD and AEBODSYS.
     */
    @Test
    void requiredVariablesOfAdaeUnderAdamAdaeWithOnlyACopiedDecodKeepTheAeNames() throws IOException
    {
        StoreMetadataProviderFactory factory = realStoreFactory();
        assumeTrue(factory != null, RealCorpusLocator.ABSENT_MESSAGE);
        MetadataProvider p = factory.forAdam("adamig", "1-1",
                List.of("standards/adam/adamig-1-1", "standards/adam/adam-adae-1-0"), List.of(),
                List.of()).orElseThrow();
        IDataTable copied = MockTable.of().col("STUDYID", "P1").col("USUBJID", "S1")
                .col("AESER", "N").col("DCDECOD", "COMPLETED").name("ADAE").build();
        StubMetadataProvider define = new StubMetadataProvider().declares("ADAE",
                AdamDataStructureDetector.OCCDS, "ADVERSE EVENT");
        assertEquals(
                List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AEDECOD", "AEBODSYS", "AESER"),
                required(p, define, copied),
                "DCDECOD names no domain, so no carried AE name is dropped (AESEQ, AETERM,"
                        + " AEDECOD, AEBODSYS reported missing)");
    }


    private static List<Object> required(MetadataProvider p, IDataTable table)
    {
        return required(p, null, table);
    }


    private static List<Object> required(MetadataProvider p, @Nullable MetadataProvider define,
            IDataTable table)
    {
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(table).ruleId("R2-1")
                .libraryProvider(p).defineProvider(define).datasetResolver(NO_RESOLVER).build());
        Vector v = LibraryLists.requiredVariables(run, List.of());
        assertTrue(v instanceof ConstVector, "a dataset-level list is one broadcast constant");
        @SuppressWarnings("unchecked")
        List<Object> names = (List<Object>) v.value(0).resolved();
        return names;
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
