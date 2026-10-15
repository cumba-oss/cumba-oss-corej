package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.define.conformance.ct.CodelistClass;
import net.cumba.corej.define.conformance.ct.CtCodelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** N2a (PLAN-define-ct-evaluation): {@link StoreCtProvider} over a store written per test. */
class StoreCtProviderTest
{

    private static final String OLD = "sdtmct-2023-12-15";

    private static final String NEW = "sdtmct-2024-03-29";

    @TempDir
    Path tempDir;

    private static StoredTerm term(String aValue, String aCode)
    {
        return new StoredTerm(aValue, aCode, aValue, null, null);
    }


    private MetadataStore store() throws IOException
    {
        // OLD: SEX (non-extensible, F/M) named "Sex"; NAMELESS (extensible, no name)
        // NEW: SEX revised (F/M/U); a codelist with a null extensible (D-6: left out); UNIT
        StoredCodelist sexOld = new StoredCodelist("SEX", "C66731", "Sex", "Sex", null, null,
                Boolean.FALSE, List.of(term("F", "C16576"), term("M", "C20197")));
        StoredCodelist nameless = new StoredCodelist("NAMELESS", "C90000", null, "Nameless", null,
                null, Boolean.TRUE, List.of(term("A", "C90001")));
        StoredCodelist sexNew = new StoredCodelist("SEX", "C66731", "Sex", "Sex", null, null,
                Boolean.FALSE,
                List.of(term("F", "C16576"), term("M", "C20197"), term("U", "C17998")));
        StoredCodelist unpublished = new StoredCodelist("NOEXT", "C91000", "No Extensible", "x",
                null, null, null, List.of(term("Q", "C91001")));
        StoredCodelist unit = new StoredCodelist("UNIT", "C71620", "Unit", "Unit", null, null,
                Boolean.TRUE, List.of(term("mg", "C28253"),
                        new StoredTerm("nocode", null, "no code", null, null)));
        // PLAN-stopped-rule-engine-fixes SR3 / T8: HAMD-17's test-code list was a Questionnaire
        // until sdtmct-2015-12-18 and a Clinical Classification from 2016-06-24 on (measured on
        // the seeded store 2026-10-12); the class follows the package that wins the c-code
        StoredCodelist hamdOld = new StoredCodelist("HAMD1TC", "C100138", "HAMD1TC Old",
                "CDISC Questionnaire HAMD 17 Test Code Terminology", null, null, Boolean.FALSE,
                List.of(term("HAMD101", "C100500")));
        StoredCodelist hamdNew = new StoredCodelist("HAMD1TC", "C100138", "HAMD1TC New",
                "CDISC Clinical Classification HAMD 17 Test Code Terminology", null, null,
                Boolean.FALSE, List.of(term("HAMD101", "C100500")));
        // A classless c-code winner, re-published later under a QRS prefix with a NEW name (so
        // the loser passes the first-wins guard, which only skips a loser whose name is taken)
        StoredCodelist tumorOld = new StoredCodelist("RSTESTCD", "C96782", "Tumor Response Old",
                "CDISC SDTM Tumor Response Assessment Test Code Terminology", null, null,
                Boolean.TRUE, List.of(term("PD", "C35571")));
        StoredCodelist tumorNew = new StoredCodelist("RSTESTCD", "C96782", "Tumor Response New",
                "CDISC Clinical Classification Tumor Response Test Code Terminology", null, null,
                Boolean.TRUE, List.of(term("PD", "C35571")));
        Path file = tempDir.resolve("store.zip");
        new MetadataStoreWriter()
                .addCtPackage(
                        new StoredCtPackage(OLD, List.of(sexOld, nameless, hamdOld, tumorOld)))
                .addCtPackage(new StoredCtPackage(NEW,
                        List.of(sexNew, unpublished, unit, hamdNew, tumorNew)))
                .publishedCtPackages(List.of(OLD, NEW)).productCatalogue(List.of()).write(file);
        return MetadataStore.open(file);
    }


    @Test
    void cCodeLookupCarriesExtensibilityAndTheSubmissionValueMap() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider provider = StoreCtProvider.of(store, List.of(NEW));
            CtCodelist unit = provider.codelistByCCode("C71620").orElseThrow();
            assertTrue(unit.extensible());
            assertEquals(Map.of("mg", "C28253"), unit.termsBySubmissionValue(),
                    "a term without a conceptId is dropped (D-6)");
            CtCodelist sex = provider.codelistByCCode("C66731").orElseThrow();
            assertFalse(sex.extensible());
            assertEquals(Map.of("F", "C16576", "M", "C20197", "U", "C17998"),
                    sex.termsBySubmissionValue());
            assertEquals(List.of(NEW), provider.packageIds());
        }
    }


    @Test
    void aCodelistWithAnUnpublishedExtensibleFlagIsUnknownToCt() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider provider = StoreCtProvider.of(store, List.of(NEW));
            assertTrue(provider.codelistByCCode("C91000").isEmpty(), "D-6: left out entirely");
            assertTrue(provider.codelistByName("No Extensible").isEmpty());
            assertEquals(4, provider.codelistCount(),
                    "SEX, UNIT, HAMD1TC and RSTESTCD; NOEXT is out");
        }
    }


    @Test
    void theFirstPackageInOrderWinsPerCCode() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider oldFirst = StoreCtProvider.of(store, List.of(OLD, NEW));
            assertEquals(Map.of("F", "C16576", "M", "C20197"),
                    oldFirst.codelistByCCode("C66731").orElseThrow().termsBySubmissionValue(),
                    "OLD first: its SEX (without U) wins");
            StoreCtProvider newFirst = StoreCtProvider.of(store, List.of(NEW, OLD));
            assertEquals(3, newFirst.codelistByCCode("C66731").orElseThrow()
                    .termsBySubmissionValue().size(), "NEW first: its SEX (with U) wins");
            assertTrue(newFirst.codelistByCCode("C90000").isPresent(),
                    "a codelist only the second package holds is still reachable");
        }
    }


    /** D-15: exact codelist name, first wins in order, a null name stays reachable by c-code. */
    @Test
    void nameLookupIsExactAndFirstWinsInOrder() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider provider = StoreCtProvider.of(store, List.of(OLD, NEW));
            assertTrue(provider.hasNameLookup());
            CtCodelist sex = provider.codelistByName("Sex").orElseThrow();
            assertEquals("C66731", sex.cCode());
            assertEquals(2, sex.termsBySubmissionValue().size(), "OLD first wins by name too");
            assertTrue(provider.codelistByName("sex").isEmpty(), "exact match, no case folding");
            assertTrue(provider.codelistByName("Nameless").isEmpty(),
                    "a codelist without a name is absent from the name index...");
            assertTrue(provider.codelistByCCode("C90000").isPresent(), "...but present by c-code");
        }
    }


    /**
     * PLAN-stopped-rule-engine-fixes SR3 / T8: the QRS class and role of a c-code's WINNING
     * codelist, from its NCI preferred term — and nothing else: a classless winner keeps no class
     * although a later package's loser of the same c-code is classed (review LOW 8).
     */
    @Test
    void theCodelistClassFollowsTheCCodeWinnerAndAClasslessWinnerKeepsNoClass() throws IOException
    {
        try (MetadataStore store = store())
        {
            StoreCtProvider newFirst = StoreCtProvider.of(store, List.of(NEW, OLD));
            CodelistClass hamd = newFirst.codelistClass("C100138").orElseThrow();
            assertEquals("Clinical Classification", hamd.qrsClass(), "NEW wins: CC since 2016");
            assertEquals(Optional.of("Test Code"), hamd.role());
            assertEquals("Questionnaire",
                    StoreCtProvider.of(store, List.of(OLD, NEW)).codelistClass("C100138")
                            .orElseThrow().qrsClass(),
                    "OLD wins: the same c-code was a Questionnaire there");
            assertTrue(newFirst.codelistClass("C66731").isEmpty(),
                    "SEX: an SDTM list is in no QRS class");
            assertTrue(newFirst.codelistClass("C99999").isEmpty(), "unknown c-code: empty");
            // the classless OLD winner (an SDTM list) is not lent NEW's class: NEW's copy passed
            // the first-wins guard (a free name) but lost the c-code
            StoreCtProvider oldFirst = StoreCtProvider.of(store, List.of(OLD, NEW));
            assertTrue(oldFirst.codelistByName("Tumor Response New").isPresent(),
                    "precondition: the loser was indexed by its free name, so it reached the"
                            + " class step");
            assertTrue(oldFirst.codelistClass("C96782").isEmpty(),
                    "a classless winner records empty; the loser's class does not leak in");
            assertEquals("Clinical Classification",
                    newFirst.codelistClass("C96782").orElseThrow().qrsClass(),
                    "NEW first: its classed copy wins the c-code");
        }
    }


    @Test
    void aPackageThatIsNotHeldFailsLoudly() throws IOException
    {
        try (MetadataStore store = store())
        {
            assertThrows(IllegalStateException.class,
                    () -> StoreCtProvider.of(store, List.of("sdtmct-1999-01-01")),
                    "the caller checks presence (D3); a vanished package is a bug, never empty");
        }
    }
}
