package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * N2c (PLAN-define-ct-evaluation §4.4): {@link StoreLibraryProvider} over a store holding one IG
 * product, its model product and a catalogue with variant keys.
 */
class StoreLibraryProviderTest
{

    @TempDir
    Path tempDir;

    private MetadataStore store() throws IOException
    {
        StoredVariable sex = StoredVariable.builder().name("SEX").label("Sex").core("Exp")
                .codelistIds(List.of("C66731")).description("Sex of the subject.")
                .notes("not the CDISC Notes").build();
        StoredVariable usubjid = StoredVariable.builder().name("USUBJID")
                .label("Unique Subject Identifier").core("Req").build();
        StoredVariable dsdecod = StoredVariable.builder().name("DSDECOD")
                .label("Standardized Disposition Term").core("Req")
                .codelistIds(List.of("C114118", "C66727")).build();
        StoredVariable noCore = StoredVariable.builder().name("NOCORE").label("No Core").build();
        StoredDataset dm = new StoredDataset("DM", "Demographics", "1", "One record per subject",
                List.of(usubjid, sex, noCore), null, null);
        StoredDataset ds = new StoredDataset("DS", "Disposition", "2", "One record per event",
                List.of(dsdecod), null, null);
        StoredProduct ig = StoredProduct.builder().key("standards/sdtmig/3-4").name("SDTMIG")
                .version("3-4").modelHref("/mdr/sdtm/2-0")
                .classes(List.of(new StoredClass("Special-Purpose", null, "1", List.of(),
                        List.of(dm), null, List.of(), List.of(), List.of())))
                .datasets(List.of(ds)).build();
        StoredVariable occur = StoredVariable.builder().name("--OCCUR")
                .label("Occurrence Indicator").build();
        StoredVariable term = StoredVariable.builder().name("--TERM").label("Reported Term")
                .build();
        StoredProduct model = StoredProduct.builder().key("models/sdtm/2-0").name("SDTM")
                .version("2-0")
                .classes(List.of(
                        new StoredClass("Events", "Events", "1", List.of(term, occur), List.of(),
                                null, List.of(), List.of(), List.of()),
                        new StoredClass("Findings", "Findings", "2", List.of(occur), List.of(),
                                null, List.of(), List.of(), List.of())))
                .build();
        Path file = tempDir.resolve("store.zip");
        new MetadataStoreWriter().addProduct(ig).addProduct(model).publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/sdtmig/3-3", "standards/sdtmig/3-4",
                        "standards/sdtmig/md-1-1", "standards/sdtmig/ap-1-0",
                        "standards/sendig/3-1", "standards/sendig/ar-1-0",
                        "standards/adam/adamig-1-3"))
                .write(file);
        return MetadataStore.open(file);
    }


    /**
     * Runbook W1b DT1 ({@code PLAN-engine-gap-define-text} Route D): the CDISC Notes the
     * define-conformance kind compares against are the store's {@code description} (675/675 SENDIG
     * 3.1.1 and 1723/1723 SDTMIG 3.3 records carry it; {@code notes} is empty on all of them) —
     * never {@code notes}, and empty for a variable without one.
     */
    @Test
    void variableDescriptionAnswersTheStoredDescriptionNotTheNotes() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreLibraryProvider p = StoreLibraryProvider.over(store);
            assertEquals(Optional.of("Sex of the subject."),
                    p.variableDescription("SDTMIG", "3.4", "DM", "SEX"));
            assertEquals(Optional.of("Sex of the subject."),
                    p.variableDescription("SDTM-IG", "3.4", "DM", "SEX"), "the 2.0 spelling");
            assertEquals(Optional.empty(), p.variableDescription("SDTMIG", "3.4", "DM", "USUBJID"),
                    "a variable without a description answers empty");
            assertEquals(Optional.empty(), p.variableDescription("SDTMIG", "3.4", "DM", "NOPE"));
        }
    }


    @Test
    void theSevenMethodsAnswerFromTheStoredFields() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreLibraryProvider p = StoreLibraryProvider.over(store);
            assertEquals(Optional.of("Demographics"), p.datasetLabel("SDTMIG", "3.4", "DM"));
            assertEquals(Optional.of("Disposition"), p.datasetLabel("SDTM-IG", "3.4", "DS"),
                    "a top-level dataset, and the 2.0 spelling of the standard");
            assertEquals(Optional.of("Sex"), p.variableLabel("SDTMIG", "3.4", "DM", "SEX"));
            assertEquals(Optional.of("C66731"),
                    p.variableCodelistCCode("SDTMIG", "3.4", "DM", "SEX"));
            assertEquals(List.of("C114118", "C66727"),
                    p.variableCodelistCCodes("SDTMIG", "3.4", "DS", "DSDECOD"), "D-12: all");
            assertEquals(Optional.of("C114118"),
                    p.variableCodelistCCode("SDTMIG", "3.4", "DS", "DSDECOD"), "the head");
            assertEquals(List.of(), p.variableCodelistCCodes("SDTMIG", "3.4", "DM", "USUBJID"));
            assertEquals(Optional.of("Req"),
                    p.variableCoreDesignation("SDTMIG", "3.4", "DM", "USUBJID"));
            assertEquals(Optional.empty(),
                    p.variableCoreDesignation("SDTMIG", "3.4", "DM", "NOCORE"),
                    "a variable without core answers empty");
            assertEquals(Optional.of("Occurrence Indicator"),
                    p.qualifierVariableLabel("SDTMIG", "3.4", "OCCUR"),
                    "modelHref -> models/sdtm/2-0 -> Events/Interventions classVariables");
            assertEquals(Optional.empty(), p.qualifierVariableLabel("SDTMIG", "3.4", "NOPE"));
            assertEquals(List.of("3.3", "3.4"), p.publishedStandardVersions("SDTMIG"),
                    "D-13: from the catalogue, variants excluded");
            assertEquals(List.of("1.1"), p.publishedStandardVersions("SDTMIG-MD"),
                    "D-11: a variant's own versions only");
            assertEquals(List.of("1.0"), p.publishedStandardVersions("SEND-IG-AR"));
            assertEquals(List.of(), p.missedProducts(), "every product asked for was held");
        }
    }


    @Test
    void aProductTheStoreLacksAnswersEmptyAndIsRecorded() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreLibraryProvider p = StoreLibraryProvider.over(store);
            assertEquals(Optional.empty(), p.datasetLabel("SDTMIG", "3.3", "DM"));
            assertEquals(Optional.empty(), p.datasetLabel("SENDIG-AR", "1.0", "DM"));
            assertEquals(List.of("standards/sdtmig/3-3", "standards/sendig/ar-1-0"),
                    p.missedProducts(), "D-14: recorded, sorted, for the basis line");
        }
    }


    @Test
    void adamAnswersEmptyAndIsNotAMiss() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreLibraryProvider p = StoreLibraryProvider.over(store);
            assertEquals(Optional.empty(), p.datasetLabel("ADaMIG", "1.3", "ADSL"));
            assertEquals(List.of(), p.publishedStandardVersions("ADaMIG"));
            assertTrue(p.missedProducts().isEmpty(), "D-10: outside the coverage, not a miss");
        }
    }


    /**
     * Review F8: the qualifier fragment is a FATESTCD value, so a root is judged as the model walk
     * judges it for FA: a root restricted to another domain does not answer, an unrestricted or
     * merely excluding one does, and the walk moves on to the other class's root of the same name.
     */
    @Test
    void aQualifierRestrictedToAnotherDomainDoesNotAnswerForFa() throws IOException
    {
        StoredVariable evdtyp = StoredVariable.builder().name("--EVDTYP")
                .label("Medical History Event Date Type").usageRestrictions("MH domain only")
                .build();
        StoredVariable eventPresp = StoredVariable.builder().name("--PRESP")
                .label("Pre-Specified (MH)").usageRestrictions("MH domain only").build();
        StoredVariable occur = StoredVariable.builder().name("--OCCUR")
                .label("Occurrence Indicator").usageRestrictions("Not in AE domain").build();
        StoredVariable method = StoredVariable.builder().name("--METHOD")
                .label("Method of Administration")
                .usageRestrictions("Not in human clinical trials; EX domain only").build();
        StoredVariable interventionPresp = StoredVariable.builder().name("--PRESP")
                .label("Pre-Specified").build();
        StoredVariable dose = StoredVariable.builder().name("--DOSE").label("Dose")
                .usageRestrictions("Not in human clinical trials").build();
        StoredProduct ig = StoredProduct.builder().key("standards/sdtmig/3-3").name("SDTMIG")
                .version("3-3").modelHref("/mdr/sdtm/1-7").build();
        StoredProduct model = StoredProduct.builder().key("models/sdtm/1-7").name("SDTM")
                .version("1-7")
                .classes(List.of(
                        new StoredClass("Events", "Events", "1", List.of(evdtyp, eventPresp, occur),
                                List.of(), null, List.of(), List.of(), List.of()),
                        new StoredClass("Interventions", "Interventions", "2",
                                List.of(method, interventionPresp, dose), List.of(), null,
                                List.of(), List.of(), List.of())))
                .build();
        Path file = tempDir.resolve("restricted.zip");
        new MetadataStoreWriter().addProduct(ig).addProduct(model).publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/sdtmig/3-3")).write(file);
        try (MetadataStore store = MetadataStore.open(file))
        {
            StoreLibraryProvider p = StoreLibraryProvider.over(store);
            assertEquals(Optional.empty(), p.qualifierVariableLabel("SDTMIG", "3.3", "EVDTYP"),
                    "MH domain only: not a qualifier FA borrows");
            assertEquals(Optional.empty(), p.qualifierVariableLabel("SDTMIG", "3.3", "METHOD"),
                    "EX domain only (the study-type clause is not applied by domain)");
            assertEquals(Optional.of("Pre-Specified"),
                    p.qualifierVariableLabel("SDTMIG", "3.3", "PRESP"),
                    "the restricted Events root is skipped, the Interventions root answers");
            assertEquals(Optional.of("Occurrence Indicator"),
                    p.qualifierVariableLabel("SDTMIG", "3.3", "OCCUR"),
                    "an exclusion of another domain leaves FA allowed");
            assertEquals(Optional.of("Dose"), p.qualifierVariableLabel("SDTMIG", "3.3", "DOSE"),
                    "a study-type restriction is not a domain restriction");
        }
    }


    /**
     * A SENDIG-AR 1.0 store with (or without) the base SENDIG 3.1 the document names: AR prints
     * {@code AG} only, the base prints {@code LB} ({@code PLAN-bugfix-engine-define} SV).
     */
    private MetadataStore arStore(boolean aWithBase) throws IOException
    {
        StoredVariable agtrt = StoredVariable.builder().name("AGTRT").label("Name of Agent")
                .core("Req").build();
        StoredDataset ag = new StoredDataset("AG", "Procedure Agents", "1", "One record per agent",
                List.of(agtrt), null, null);
        StoredProduct ar = StoredProduct.builder().key("standards/sendig/ar-1-0").name("SENDIG-AR")
                .version("1-0").classes(List.of(new StoredClass("Interventions", null, "1",
                        List.of(), List.of(ag), null, List.of(), List.of(), List.of())))
                .build();
        StoredVariable lbtest = StoredVariable.builder().name("LBTEST")
                .label("Lab Test or Examination Name").core("Exp").codelistIds(List.of("C65047"))
                .build();
        StoredDataset lb = new StoredDataset("LB", "Laboratory Test Results", "1",
                "One record per lab test", List.of(lbtest), null, null);
        StoredProduct base = StoredProduct.builder().key("standards/sendig/3-1").name("SENDIG")
                .version("3-1").classes(List.of(new StoredClass("Findings", null, "1", List.of(),
                        List.of(lb), null, List.of(), List.of(), List.of())))
                .build();
        MetadataStoreWriter writer = new MetadataStoreWriter().addProduct(ar);
        if (aWithBase)
        {
            writer.addProduct(base);
        }
        Path file = tempDir.resolve("ar-store.zip");
        writer.publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/sendig/3-1", "standards/sendig/ar-1-0"))
                .write(file);
        return MetadataStore.open(file);
    }


    /**
     * {@code PLAN-bugfix-engine-define} SV / {@code PLAN-send-base-ig-view} {@code LVC-F1} (b): the
     * define side reads the base-IG view too — a SENDIG-AR 1.0 define binding {@code LB} asks the
     * library for a dataset AR does not print and is answered from the base SENDIG 3.1 (label,
     * codelist, core, dataset label), exactly as the data side's provider is.
     */
    @Test
    void anArDatasetTheIgDoesNotPrintIsAnsweredFromTheBaseSendig() throws IOException
    {
        try (MetadataStore store = arStore(true))
        {
            StoreLibraryProvider p = StoreLibraryProvider.over(store);
            assertEquals(Optional.of("Lab Test or Examination Name"),
                    p.variableLabel("SENDIG-AR", "1.0", "LB", "LBTEST"), "label from the base");
            assertEquals(Optional.of("C65047"),
                    p.variableCodelistCCode("SENDIG-AR", "1.0", "LB", "LBTEST"));
            assertEquals(Optional.of("Exp"),
                    p.variableCoreDesignation("SENDIG-AR", "1.0", "LB", "LBTEST"));
            assertEquals(Optional.of("Laboratory Test Results"),
                    p.datasetLabel("SENDIG-AR", "1.0", "LB"));
            assertEquals(Optional.of("Name of Agent"),
                    p.variableLabel("SENDIG-AR", "1.0", "AG", "AGTRT"), "the IG's own dataset");
            assertEquals(List.of(), p.missedProducts(), "the base was held: nothing missed");
        }
    }


    /**
     * The base the store lacks: the AR product answers as stored (its own datasets), the base's
     * datasets stay empty, and the base key is recorded for the run's library-basis line (D-14).
     */
    @Test
    void anArWithoutItsBaseAnswersItsOwnDatasetsAndRecordsTheMissingBase() throws IOException
    {
        try (MetadataStore store = arStore(false))
        {
            StoreLibraryProvider p = StoreLibraryProvider.over(store);
            assertEquals(Optional.of("Name of Agent"),
                    p.variableLabel("SENDIG-AR", "1.0", "AG", "AGTRT"));
            assertEquals(Optional.empty(), p.variableLabel("SENDIG-AR", "1.0", "LB", "LBTEST"));
            assertEquals(List.of("standards/sendig/3-1"), p.missedProducts(),
                    "the missing base is a miss the basis line names");
        }
    }


    /** D-11: the key grammar the catalogue really uses. */
    @Test
    void igKeysFoldSpellingsAndMapVariantsToTheirRealKeys()
    {
        assertEquals(Optional.of("standards/sdtmig/3-4"),
                StoreLibraryProvider.igKey("SDTMIG", "3.4"));
        assertEquals(Optional.of("standards/sdtmig/3-4"),
                StoreLibraryProvider.igKey("SDTM-IG", "3.4"));
        assertEquals(Optional.of("standards/sdtmig/3-1-2"),
                StoreLibraryProvider.igKey("sdtmig", "3.1.2"));
        assertEquals(Optional.of("standards/sdtmig/md-1-1"),
                StoreLibraryProvider.igKey("SDTMIG-MD", "1.1"));
        assertEquals(Optional.of("standards/sdtmig/ap-1-0"),
                StoreLibraryProvider.igKey("SDTMIG-AP", "1.0"));
        assertEquals(Optional.of("standards/sendig/ar-1-0"),
                StoreLibraryProvider.igKey("SEND-IG-AR", "1.0"));
        assertEquals(Optional.of("standards/sendig/dart-1-1"),
                StoreLibraryProvider.igKey("SENDIG-DART", "1.1"));
        assertEquals(Optional.of("standards/sendig/genetox-1-0"),
                StoreLibraryProvider.igKey("SENDIG-GENETOX", "1.0"));
        assertEquals(Optional.empty(), StoreLibraryProvider.igKey("ADaMIG", "1.3"));
        assertEquals(Optional.empty(), StoreLibraryProvider.igKey("SDTMIG", " "));
    }
}
