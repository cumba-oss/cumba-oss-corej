package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PLAN-store-library-content-errata D2 / §2.5: the model walk behind {@code get_model_column_order}
 * honours {@code usageRestrictions}. A class-table root restricted to a domain is part of that
 * domain's walk and of no other ({@code --EVDTYP} "MH domain only": MHEVDTYP yes, AEEVDTYP no), a
 * "Not in AE domain" variable leaves the AE walk, a "Not in Findings class domains" timing variable
 * leaves every Findings walk, and an unparseable text restricts nothing. Both algorithms — the pure
 * model walk (A) and the IG-merged walk (B) — read the same filter, and the IG's own variables are
 * never filtered.
 */
class ModelWalkUsageRestrictionsTest
{

    private static StoredVariable modelVar(String aName, String aOrdinal, String aRole,
            String aRestriction)
    {
        return StoredVariable.builder().name(aName).label(aName + " label").ordinal(aOrdinal)
                .role(aRole).simpleDatatype("Char").usageRestrictions(aRestriction).build();
    }


    private static StoredVariable igVar(String aName, String aOrdinal, String aRole)
    {
        return StoredVariable.builder().name(aName).label(aName + " IG label").ordinal(aOrdinal)
                .role(aRole).simpleDatatype("Char").core("Exp").build();
    }


    private static StoredClass modelClass(String aName, String aOrdinal, List<StoredVariable> aVars)
    {
        return new StoredClass(aName, aName, aOrdinal, aVars, List.of(), null, List.of(), List.of(),
                List.of());
    }


    /** The 1.7-after-errata / 2.0 shape: restricted roots in the class tables. */
    private static StoredProduct modelProduct()
    {
        List<StoredVariable> genObs = List.of(modelVar("STUDYID", "1", "Identifier", null),
                modelVar("USUBJID", "2", "Identifier", null),
                modelVar("--STDTC", "3", "Timing", "Not in Findings class domains"),
                modelVar("--DTC", "4", "Timing", null),
                modelVar("FETUSID", "5", "Identifier", "Not in human clinical trials"));
        List<StoredVariable> events = List.of(modelVar("--TERM", "1", "Topic", null),
                modelVar("--DECOD", "2", "Synonym Qualifier", null),
                modelVar("--EVDTYP", "3", "Variable Qualifier", "MH domain only"),
                modelVar("--OCCUR", "4", "Record Qualifier", "Not in AE domain"),
                modelVar("--SINTV", "5", "Record Qualifier", "AE domain only"),
                modelVar("--ODD", "6", "Record Qualifier", "Only on Tuesdays"));
        List<StoredVariable> findings = List.of(modelVar("--TESTCD", "1", "Topic", null),
                modelVar("--TEST", "2", "Synonym Qualifier", null),
                modelVar("--ORRES", "3", "Result Qualifier", null),
                modelVar("--CHROM", "4", "Variable Qualifier", "GF domain only"),
                modelVar("--TSTCND", "5", "Record Qualifier", "CP, IS, and LB domains only"));
        List<StoredVariable> fa = List.of(modelVar("--OBJ", "1", "Record Qualifier", null));
        return StoredProduct.builder().key("models/sdtm/2-0").name("SDTM").version("2-0")
                .classes(List.of(modelClass("General Observations", "1", genObs),
                        modelClass("Events", "2", events), modelClass("Findings", "3", findings),
                        modelClass("Findings About", "4", fa)))
                .build();
    }


    /** The IG: AE, MH, LB, GF and FA datasets, MH carrying MHEVDTYP as SDTMIG 3.3 does. */
    private static StoredProduct igProduct()
    {
        StoredDataset ae = new StoredDataset("AE", "Adverse Events", "1", "",
                List.of(igVar("AETERM", "1", "Topic"), igVar("AESTDTC", "2", "Timing")), null,
                null);
        StoredDataset mh = new StoredDataset("MH", "Medical History", "2", "", List
                .of(igVar("MHTERM", "1", "Topic"), igVar("MHEVDTYP", "2", "Variable Qualifier")),
                null, null);
        StoredDataset lb = new StoredDataset("LB", "Laboratory", "3", "",
                List.of(igVar("LBTESTCD", "1", "Topic")), null, null);
        StoredDataset gf = new StoredDataset("GF", "Genomics Findings", "4", "",
                List.of(igVar("GFTESTCD", "1", "Topic")), null, null);
        StoredDataset fa = new StoredDataset("FA", "Findings About", "5", "",
                List.of(igVar("FATESTCD", "1", "Topic")), null, null);
        return StoredProduct.builder().key("standards/sdtmig/3-4").name("SDTMIG").version("3-4")
                .classes(List.of(
                        new StoredClass("Events", "Events", "1", List.of(), List.of(ae, mh), null,
                                List.of(), List.of(), List.of()),
                        new StoredClass("Findings", "Findings", "2", List.of(), List.of(lb, gf),
                                null, List.of(), List.of(), List.of()),
                        new StoredClass("Findings About", "Findings About", "3", List.of(),
                                List.of(fa), null, List.of(), List.of(), List.of())))
                .build();
    }


    private static IDataTable mock(String aDomain)
    {
        return MockTable.of().name(aDomain).col("DOMAIN", aDomain).build();
    }


    private static MetadataLibraryProvider provider()
    {
        var builder = lib("study");
        for (String d : List.of("AE", "MH", "LB", "GF", "FA"))
        {
            builder = builder.table(table(d).build());
        }
        IMetadataLibrary study = builder.build();
        return MetadataLibraryProvider.forStoredSdtm(study, igProduct(), modelProduct(), "sdtmig",
                "3-4", null, null, null);
    }


    private static List<String> modelWalk(String aDomain)
    {
        List<String> names = provider().getStandardModelVariables(mock(aDomain), null);
        assertNotNull(names);
        return names;
    }


    private static List<String> mergedWalk(String aDomain)
    {
        List<Map<String, String>> rows = provider().getStandardVariablesDetailed(mock(aDomain),
                null);
        assertNotNull(rows);
        return rows.stream().map(r -> r.get("name")).toList();
    }


    @Test
    @DisplayName("⭐ a domain-only root is walked for its domain and for no other (AEEVDTYP vs MHEVDTYP)")
    void domainOnlyRootsBelongToTheirDomainOnly()
    {
        List<String> mh = modelWalk("MH");
        assertTrue(mh.contains("MHEVDTYP"), mh.toString());
        assertFalse(mh.contains("MHSINTV"), "AE domain only: " + mh);
        List<String> ae = modelWalk("AE");
        assertFalse(ae.contains("AEEVDTYP"), "the SDTM 2.0 under-report (§2.5): " + ae);
        assertTrue(ae.contains("AESINTV"), ae.toString());
        assertEquals(
                List.of("STUDYID", "USUBJID", "FETUSID", "MHTERM", "MHDECOD", "MHEVDTYP", "MHOCCUR",
                        "MHODD", "MHSTDTC", "MHDTC"),
                mh, "order and the unrestricted rest are unchanged (FETUSID: a study-type clause is"
                        + " not applied by the domain walk)");
    }


    @Test
    void notInDomainLeavesThatDomainsWalk()
    {
        assertFalse(modelWalk("AE").contains("AEOCCUR"));
        assertTrue(modelWalk("MH").contains("MHOCCUR"));
    }


    @Test
    void notInFindingsClassLeavesEveryFindingsWalkIncludingFindingsAbout()
    {
        List<String> lb = modelWalk("LB");
        assertFalse(lb.contains("LBSTDTC"), lb.toString());
        assertTrue(lb.contains("LBDTC"), lb.toString());
        assertTrue(modelWalk("AE").contains("AESTDTC"));
        List<String> fa = modelWalk("FA");
        assertFalse(fa.contains("FASTDTC"), fa.toString());
        assertTrue(fa.contains("FAOBJ"), "the FA splice still happens: " + fa);
        assertFalse(fa.contains("FACHROM"),
                "GF domain only, also through the FINDINGS splice: " + fa);
    }


    @Test
    void domainListsAndUnparseableAndTrialTypeTexts()
    {
        assertTrue(modelWalk("LB").contains("LBTSTCND"));
        assertFalse(modelWalk("GF").contains("GFTSTCND"));
        assertTrue(modelWalk("GF").contains("GFCHROM"));
        assertTrue(modelWalk("AE").contains("AEODD"), "an unparseable text restricts nothing");
        assertTrue(modelWalk("AE").contains("FETUSID"),
                "a study-type clause is recognised and deliberately not applied by the domain walk");
    }


    @Test
    void theIgMergedWalkFiltersTheModelSideAndNeverTheIgSide()
    {
        List<String> ae = mergedWalk("AE");
        assertFalse(ae.contains("AEEVDTYP"), ae.toString());
        assertFalse(ae.contains("AEOCCUR"), ae.toString());
        assertTrue(ae.contains("AESTDTC"), "the IG's own variable, whatever the model says: " + ae);
        List<String> mh = mergedWalk("MH");
        assertTrue(mh.contains("MHEVDTYP"), mh.toString());
        List<String> lb = mergedWalk("LB");
        assertFalse(lb.contains("LBSTDTC"), lb.toString());
    }
}
