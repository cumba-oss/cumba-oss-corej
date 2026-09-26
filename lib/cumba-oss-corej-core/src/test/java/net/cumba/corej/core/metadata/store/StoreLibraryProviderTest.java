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
                .codelistIds(List.of("C66731")).build();
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
