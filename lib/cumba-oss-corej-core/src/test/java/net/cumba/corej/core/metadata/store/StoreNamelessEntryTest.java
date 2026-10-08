package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.metadata.CdiscLibraryMetadataLibrary;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 NN (§3 table B, {@code FINDINGS-unowned-residuals} I2; W2-D5): a store entry without a
 * name — a {@link StoredVariable} or a dataset / data structure — is skipped, not served as a
 * present {@code ""}, and a skipped variable consumes no ordinal, so the served columns stay
 * numbered 0, 1, 2, … without a gap (the NNL §1 skip precedent).
 */
class StoreNamelessEntryTest
{

    private static StoredVariable variable(String aName, String aOrdinal)
    {
        return StoredVariable.builder().name(aName).label(aName + " label").ordinal(aOrdinal)
                .core("Req").simpleDatatype("Char").build();
    }


    private static List<String> names(IDataTableMetadata aTable)
    {
        return aTable.getColumns().stream().map(IColumnMetadata::getName).toList();
    }


    private static List<Integer> indexes(IDataTableMetadata aTable)
    {
        return aTable.getColumns().stream().map(IColumnMetadata::getIndex).toList();
    }


    private static List<String> tableNames(CdiscLibraryMetadataLibrary aLibrary)
    {
        return aLibrary.getDataTables().stream().map(IDataTableMetadata::getName).toList();
    }


    @Test
    void sdtmNamelessVariablesAndDatasetsAreSkipped()
    {
        StoredDataset dm = new StoredDataset(
                "DM", "Demographics", "1", null, List.of(variable("STUDYID", "1"),
                        variable(null, "2"), variable("", "3"), variable("USUBJID", "4")),
                null, null);
        StoredDataset nameless = new StoredDataset(null, "No name", "2", null,
                List.of(variable("X", "1")), null, null);
        StoredDataset blank = new StoredDataset("", "Blank name", "3", null,
                List.of(variable("Y", "1")), null, null);
        StoredProduct product = StoredProduct.builder().key(MetadataStoreFixtures.IG_KEY)
                .name("SDTMIG v3.4").version("3-4")
                .classes(List.of(new StoredClass("SpecialPurpose", "Special-Purpose", "1",
                        List.of(), List.of(dm, nameless, blank), null, List.of(), List.of(),
                        List.of())))
                .build();

        CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary.fromStoredSdtm("sdtmig",
                "3-4", product, List.of(MetadataStoreFixtures.sdtmPackage1()), List.of());

        assertEquals(List.of("DM"), tableNames(library), "a nameless dataset is not served");
        assertTrue(library.getDataTable("").isEmpty(), "no table answers for the empty name");
        IDataTableMetadata table = library.getDataTable("DM").orElseThrow();
        assertEquals(List.of("STUDYID", "USUBJID"), names(table));
        assertEquals(List.of(0, 1), indexes(table), "a skipped variable consumes no ordinal");
    }


    @Test
    void adamNamelessVariablesAndDataStructuresAreSkipped()
    {
        StoredDataStructure adsl = new StoredDataStructure("ADSL", "Subject-Level", "1",
                "SUBJECT LEVEL ANALYSIS DATASET", null,
                List.of(new StoredVariableSet("Identifier", "Identifier Variables", "1",
                        List.of(variable("STUDYID", "1"), variable(null, "2"),
                                variable("USUBJID", "3")),
                        null)),
                null);
        StoredDataStructure nameless = new StoredDataStructure(
                null, "No name", "2", "BDS", null, List.of(new StoredVariableSet("Identifier",
                        "Identifier Variables", "1", List.of(variable("PARAMCD", "1")), null)),
                null);
        StoredProduct product = StoredProduct.builder().key(MetadataStoreFixtures.ADAM_KEY)
                .name("ADaMIG v1.3").version("1-3").dataStructures(List.of(adsl, nameless)).build();

        CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary.fromStoredAdam("adamig",
                "1-3", product, List.of(MetadataStoreFixtures.sdtmPackage1()), List.of(),
                List.of());

        assertEquals(List.of("ADSL"), tableNames(library),
                "a nameless data structure is not served");
        IDataTableMetadata table = library.getDataTable("ADSL").orElseThrow();
        assertEquals(List.of("STUDYID", "USUBJID"), names(table));
        assertEquals(List.of(0, 1), indexes(table), "a skipped variable consumes no ordinal");
    }
}
