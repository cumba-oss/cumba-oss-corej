package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.MetadataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link StoreMetadataProviderFactory#forSdtm} composes the base-IG view
 * ({@code PLAN-send-base-ig-view}): an AR run answers a carried domain from the base SENDIG —
 * label, core, column order, dataset label, {@code SUPPQUAL} — keeps its own spec for a domain it
 * prints, and degrades to today's answer when the store lacks the base.
 */
class StoreMetadataProviderFactoryBaseIgViewTest
{

    @TempDir
    private Path temp;

    @Test
    void anArRunAnswersACarriedDomainFromTheBaseSendig() throws IOException
    {
        MetadataProvider ar = provider(true, "ar-1-0");

        Map<String, String> lbtestcd = ar.getVariableMetadata("LB", "LBTESTCD");
        assertEquals("Lab Test or Examination Short Name", lbtestcd.get("label"));
        assertEquals("Req", lbtestcd.get("core"));
        assertEquals(List.of("STUDYID", "LBTESTCD", "LBORRES"), ar.getColumnOrder("LB"));
        assertEquals("Laboratory Test Results", ar.getDatasetMetadata("LB").get("label"));
        assertFalse(ar.isDomainCustom("LB"));
        assertTrue(ar.isDomainCustom("ZZ"));
    }


    @Test
    void anArRunAnswersSuppqualFromTheBase() throws IOException
    {
        MetadataProvider ar = provider(true, "ar-1-0");

        // B-SUPP: SENDIG-AR prints no SUPPQUAL; the base's comes in like any other dataset.
        assertEquals("Qualifier Variable Name",
                ar.getVariableMetadata("SUPPQUAL", "QNAM").get("label"));
        assertEquals("Supplemental Qualifiers for [domain name]",
                ar.getDatasetMetadata("SUPPQUAL").get("label"));
    }


    @Test
    void aDomainTheRunsIgPrintsKeepsItsOwnSpecification() throws IOException
    {
        MetadataProvider ar = provider(true, "ar-1-0");

        assertEquals("AR Demographics", ar.getDatasetMetadata("DM").get("label"));
        assertEquals(List.of("STUDYID", "ARONLY"), ar.getColumnOrder("DM"));
    }


    @Test
    void aStoreWithoutTheBaseGivesTodaysAnswer() throws IOException
    {
        MetadataProvider ar = provider(false, "ar-1-0");

        assertEquals(Map.of(), ar.getVariableMetadata("LB", "LBTESTCD"));
        assertEquals(Map.of(), ar.getDatasetMetadata("LB"));
        assertTrue(ar.isDomainCustom("LB"));
    }


    @Test
    void aRunOutsideTheScopeIsUnchanged() throws IOException
    {
        // The base itself (B-SCOPE: SENDIG 3.1 has no base view) still answers only its own
        // datasets: AR's MB never appears in it.
        MetadataProvider base = provider(true, "3-1");

        assertEquals(Map.of(), base.getDatasetMetadata("MB"));
        assertEquals("Laboratory Test Results", base.getDatasetMetadata("LB").get("label"));
    }


    private MetadataProvider provider(boolean aWithBase, String aVersion) throws IOException
    {
        Path file = temp.resolve("store-" + aWithBase + ".zip");
        MetadataStoreWriter writer = new MetadataStoreWriter().addProduct(ar())
                .addProduct(model("1-8")).addProduct(model("1-5")).publishedCtPackages(List.of())
                .productCatalogue(List.of());
        if (aWithBase)
        {
            writer.addProduct(sendig31());
        }
        writer.write(file);
        return StoreMetadataProviderFactory.open(file).forSdtm("sendig", aVersion, List.of())
                .orElseThrow();
    }


    private static StoredVariable variable(String aName, String aLabel, String aOrdinal,
            String aCore, String aRole)
    {
        return StoredVariable.builder().name(aName).label(aLabel).ordinal(aOrdinal).core(aCore)
                .role(aRole).simpleDatatype("Char").build();
    }


    private static StoredClass klass(String aName, String aOrdinal, StoredDataset... aDatasets)
    {
        return new StoredClass(aName, aName, aOrdinal, List.of(), List.of(aDatasets), null,
                List.of(), List.of(), List.of());
    }


    private static StoredProduct ar()
    {
        StoredDataset dm = new StoredDataset("DM", "AR Demographics", "1", "One record per subject",
                List.of(variable("STUDYID", "Study Identifier", "1", "Req", "Identifier"),
                        variable("ARONLY", "An AR-only variable", "2", "Perm", "Record Qualifier")),
                null, null);
        StoredDataset mb = new StoredDataset("MB", "Microbiology Specimen", "2", "One record per",
                List.of(variable("MBTESTCD", "Test Short Name", "1", "Req", "Topic")), null, null);
        return StoredProduct.builder().key("standards/sendig/ar-1-0").name("SENDIG-AR v1.0")
                .version("1.0").modelHref("/mdr/sdtm/1-8")
                .classes(List.of(klass("Special-Purpose", "2", dm), klass("Findings", "5", mb),
                        klass("Trial Design", "7")))
                .build();
    }


    private static StoredProduct sendig31()
    {
        StoredDataset dm = new StoredDataset("DM", "Demographics", "1", "One record per subject",
                List.of(variable("STUDYID", "Study Identifier", "1", "Req", "Identifier")), null,
                null);
        StoredDataset lb = new StoredDataset("LB", "Laboratory Test Results", "11",
                "One record per lab test per time point per visit per subject",
                List.of(variable("STUDYID", "Study Identifier", "1", "Req", "Identifier"),
                        variable("LBTESTCD", "Lab Test or Examination Short Name", "2", "Req",
                                "Topic"),
                        variable("LBORRES", "Result or Finding as Collected", "3", "Exp",
                                "Result Qualifier")),
                null, null);
        StoredDataset suppqual = new StoredDataset("SUPPQUAL",
                "Supplemental Qualifiers for [domain name]", "29", "One record per IDVAR",
                List.of(variable("QNAM", "Qualifier Variable Name", "1", "Req", "Topic")), null,
                null);
        return StoredProduct.builder().key("standards/sendig/3-1").name("SENDIG v3.1")
                .version("3.1").modelHref("/mdr/sdtm/1-5")
                .classes(List.of(klass("Special-Purpose", "2", dm), klass("Findings", "5", lb),
                        klass("Relationship", "7", suppqual)))
                .build();
    }


    private static StoredProduct model(String aVersion)
    {
        return StoredProduct.builder().key("models/sdtm/" + aVersion).name("SDTM " + aVersion)
                .version(aVersion)
                .classes(List.of(new StoredClass("Findings", "Findings", "4",
                        List.of(variable("--TESTCD", "Short Name", "1", null, "Topic")), List.of(),
                        null, List.of(), List.of(), List.of())))
                .build();
    }
}
